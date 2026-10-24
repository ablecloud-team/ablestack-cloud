<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Licensed under the Apache License,
Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <section class="storage-configuration-operation" aria-live="polite">
    <template v-if="history">
      <div class="detail-tab-toolbar">
        <a-button :loading="loading" @click="loadHistory">
          <template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}
        </a-button>
      </div>
      <a-alert v-if="readFailed" type="warning" show-icon :message="$t('message.storage.configuration.observation.unavailable')" />
      <a-table :columns="columns" :data-source="rows" row-key="id" :loading="loading" :pagination="{ pageSize: 20 }" :scroll="{ x: 720 }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'state'">{{ stateLabel(record.state) }}</template>
          <template v-else-if="column.key === 'details'"><a-button @click="selected = record">{{ $t('label.details') }}</a-button></template>
        </template>
      </a-table>
      <MoldDialog v-if="selected" :title="$t('label.storage.configuration.diagnostics')" size="md" @cancel="selected = null">
        <StorageConfigurationOperationPanel :operation="selected" @refresh="refreshSelected" @resume="$emit('resume', selected)" />
      </MoldDialog>
    </template>
    <template v-else-if="operation">
      <a-alert :type="alertType" show-icon :message="operation.originalfailure?.phase === 'PREFLIGHT' ? $t('label.storage.preflight.blocked') : stateLabel(operation.state)" :description="$t('message.storage.configuration.' + descriptionKey)" />
      <a-descriptions bordered size="small" :column="1" class="storage-configuration-operation__summary">
        <a-descriptions-item :label="$t('label.storage.configuration.request')"><code>{{ operation.requestid || '-' }}</code></a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.configuration.phase')">{{ phaseLabel(operation.phase) }}</a-descriptions-item>
        <a-descriptions-item v-if="!['COMMITTED', 'ROLLED_BACK'].includes(operation.state)" :label="$t('label.storage.preflight.cancel')">{{ $t('message.storage.preflight.cancel.unsupported') }}</a-descriptions-item>
        <a-descriptions-item v-if="operation.id" :label="$t('label.id')"><code>{{ operation.id }}</code></a-descriptions-item>
        <a-descriptions-item v-if="operation.originalfailure?.code" :label="$t('label.storage.configuration.first.failure')">
          <code>{{ operation.originalfailure.code }}</code> · {{ phaseLabel(operation.originalfailure.phase) }}
        </a-descriptions-item>
        <a-descriptions-item v-if="operation.rollback?.state" :label="$t('label.storage.configuration.rollback')">
          {{ $t('label.storage.configuration.rollback.' + operation.rollback.state.toLowerCase()) }}
          <code v-if="operation.rollback.code">{{ operation.rollback.code }}</code>
        </a-descriptions-item>
      </a-descriptions>
      <a-descriptions v-if="operation.observation?.execution" bordered size="small" :column="1" class="storage-configuration-operation__summary">
        <a-descriptions-item :label="$t('label.storage.preflight.heartbeat')">{{ executionTime(operation.observation.execution.heartbeatAt) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.preflight.deadline')">{{ executionTime(operation.observation.execution.softDeadlineAt) }}</a-descriptions-item>
      </a-descriptions>
      <a-alert v-if="operation.observation?.execution?.overdue" type="warning" show-icon :message="$t('message.storage.preflight.overdue')" />
      <a-alert v-if="operation.observationwarning || operation.observation?.observationAvailable === false" type="warning" show-icon :message="$t('message.storage.configuration.observation.unavailable')" />
      <div class="detail-tab-toolbar storage-configuration-operation__toolbar">
        <a-button @click="$emit('refresh')"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
        <a-button v-if="operation.canresume" type="primary" @click="$emit('resume', operation)">{{ $t('label.storage.configuration.resume') }}</a-button>
      </div>
    </template>
  </section>
</template>
<script>
import { getAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { decodeConfigurationOperation, decodeConfigurationOperations } from '@/utils/storageConfigOperation'
export default {
  name: 'StorageConfigurationOperationPanel',
  components: { MoldDialog, ReloadOutlined },
  props: { operation: { type: Object, default: null }, history: Boolean, instanceid: { type: String, default: '' } },
  emits: ['refresh', 'resume'],
  data () { return { rows: [], loading: false, readFailed: false, selected: null, generation: 0 } },
  computed: {
    columns () {
      return [
        { title: this.$t('label.created'), dataIndex: 'created', key: 'created' },
        { title: this.$t('label.protocol'), dataIndex: 'protocol', key: 'protocol' },
        { title: this.$t('label.state'), key: 'state' },
        { title: this.$t('label.details'), key: 'details' }
      ]
    },
    alertType () { return this.operation?.state === 'COMMITTED' ? 'success' : this.operation?.state === 'ROLLED_BACK' ? 'warning' : 'info' },
    descriptionKey () {
      if (this.operation?.originalfailure?.phase === 'PREFLIGHT') return 'preflight.blocked'
      if (this.operation?.state === 'ROLLED_BACK') return 'rolled.back'
      if (this.operation?.state === 'COMMITTED') return 'committed'
      if (['RECOVERY_REQUIRED', 'OBSERVATION_UNAVAILABLE', 'SUBMISSION_UNCONFIRMED'].includes(this.operation?.state)) return 'recovery.required'
      return 'pending'
    }
  },
  watch: { instanceid: { immediate: true, handler () { this.generation++; this.rows = []; this.selected = null; if (this.history && this.instanceid) this.loadHistory() } } },
  beforeUnmount () { this.generation++ },
  methods: {
    executionTime (value) { return value ? new Date(Number(value) * 1000).toLocaleString() : '-' },
    stateLabel (state) { return this.$t('label.storage.configuration.state.' + String(state || 'QUEUED').toLowerCase()) },
    phaseLabel (phase) { return this.$t('label.storage.configuration.phase.' + String(phase || 'PREPARING').toLowerCase()) },
    async loadHistory () {
      const generation = ++this.generation
      this.loading = true
      try {
        const wire = await getAPI('listStorageServiceConfigurationOperations', { instanceid: this.instanceid, page: 1, pagesize: 200 })
        if (generation !== this.generation) return
        this.rows = decodeConfigurationOperations(wire); this.readFailed = false
      } catch (error) { if (generation === this.generation) this.readFailed = true } finally { if (generation === this.generation) this.loading = false }
    },
    async refreshSelected () {
      if (!this.selected) return
      const id = this.selected.id; const generation = this.generation
      try {
        const wire = await getAPI('getStorageServiceConfigurationOperation', { instanceid: this.instanceid, id })
        if (generation === this.generation && this.selected?.id === id) this.selected = decodeConfigurationOperation(wire) || this.selected
      } catch (error) { if (this.selected?.id === id) this.selected = { ...this.selected, observationwarning: true } }
    }
  }
}
</script>
<style scoped>
.storage-configuration-operation { color: var(--text-color); min-width: 0; }
.storage-configuration-operation__summary { margin-top: 16px; }
.storage-configuration-operation__toolbar { margin-top: 16px; }
.storage-configuration-operation code { overflow-wrap: anywhere; color: inherit; }
.storage-configuration-operation :deep(.ant-descriptions-item-label),
.storage-configuration-operation :deep(.ant-descriptions-item-content) { color: var(--text-color); }
</style>
