<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->
<template>
  <a-spin :spinning="loading">
    <div class="cross-dr-tab-toolbar">
      <dr-checkpoint-manager :planId="planId" />
      <a-button size="small" @click="fetchData">
        <template #icon><ReloadOutlined /></template>
        {{ $t('label.refresh') }}
      </a-button>
    </div>
    <a-alert
      type="info"
      show-icon
      class="cross-dr-checkpoint-note"
      :message="$t('message.dr.sync.checkpoint.help')" />
    <a-table
      size="small"
      :columns="columns"
      :dataSource="checkpoints"
      :rowKey="record => record.id"
      :scroll="{ x: 1320 }"
      :pagination="{ pageSize: 10 }">
      <template #bodyCell="{ column, text }">
        <template v-if="column.key === 'state'">
          <dr-status-pill :status="text" />
        </template>
        <template v-else-if="column.key === 'targetreadyrposeconds' || column.key === 'sourcerposeconds'">
          {{ formatSeconds(text) }}
        </template>
        <template v-else-if="byteColumns.includes(column.key)">
          {{ formatBytes(text) }}
        </template>
        <template v-else-if="column.key === 'durationms'">
          {{ formatDuration(text) }}
        </template>
        <template v-else-if="column.key === 'incrementalverified'">
          {{ text === true ? $t('label.yes') : text === false ? $t('label.no') : '-' }}
        </template>
        <template v-else-if="column.key === 'automaticreseed'">
          {{ text === true ? $t('label.yes') : text === false ? $t('label.no') : '-' }}
        </template>
        <template v-else-if="column.key === 'transferratio'">
          {{ formatTransferRatio(text) }}
        </template>
        <template v-else-if="column.key === 'metricsestimated'">
          {{ text === true ? $t('label.dr.metrics.estimated') : text === false ? $t('label.dr.metrics.measured') : '-' }}
        </template>
      </template>
    </a-table>
  </a-spin>
</template>

<script>
import DrCheckpointManager from '@/components/dr/DrCheckpointManager.vue'
import DrStatusPill from '@/components/dr/DrStatusPill.vue'
import { listDrRestorePoints, listDrSyncCheckpoints } from '@/api/dr'

export default {
  name: 'DrSyncCheckpointsTab',
  components: { DrStatusPill, DrCheckpointManager },
  props: {
    planId: { type: String, required: true }
  },
  data () {
    return {
      loading: false,
      checkpoints: [],
      byteColumns: ['changedbytes', 'sourcereadbytes', 'targetwrittenbytes', 'transferpayloadbytes', 'throughputbps'],
      columns: [
        { key: 'checkpointsequence', title: this.$t('label.dr.checkpoint.sequence'), dataIndex: 'checkpointsequence' },
        { key: 'state', title: this.$t('label.state'), dataIndex: 'state' },
        { key: 'requestedmode', title: this.$t('label.dr.requested.mode'), dataIndex: 'requestedmode' },
        { key: 'effectivemode', title: this.$t('label.dr.effective.mode'), dataIndex: 'effectivemode' },
        { key: 'automaticreseed', title: this.$t('label.dr.automatic.reseed'), dataIndex: 'automaticreseed' },
        { key: 'reseedreason', title: this.$t('label.dr.reseed.reason'), dataIndex: 'reseedreason' },
        { key: 'incrementalverified', title: this.$t('label.dr.incremental.verified'), dataIndex: 'incrementalverified' },
        { key: 'metricsestimated', title: this.$t('label.dr.metrics.kind'), dataIndex: 'metricsestimated' },
        { key: 'changedbytes', title: this.$t('label.dr.changed.bytes'), dataIndex: 'changedbytes' },
        { key: 'sourcereadbytes', title: this.$t('label.dr.source.read.bytes'), dataIndex: 'sourcereadbytes' },
        { key: 'targetwrittenbytes', title: this.$t('label.dr.target.written.bytes'), dataIndex: 'targetwrittenbytes' },
        { key: 'transferpayloadbytes', title: this.$t('label.dr.transfer.payload.bytes'), dataIndex: 'transferpayloadbytes' },
        { key: 'transferratio', title: this.$t('label.dr.transfer.ratio'), dataIndex: 'transferratio' },
        { key: 'changedextentcount', title: this.$t('label.dr.changed.extent.count'), dataIndex: 'changedextentcount' },
        { key: 'durationms', title: this.$t('label.dr.transfer.duration'), dataIndex: 'durationms' },
        { key: 'throughputbps', title: this.$t('label.dr.transfer.throughput'), dataIndex: 'throughputbps' },
        { key: 'sourcecreated', title: this.$t('label.dr.source.checkpoint.at'), dataIndex: 'sourcecreated' },
        { key: 'targetreadyat', title: this.$t('label.dr.target.ready.at'), dataIndex: 'targetreadyat' },
        { key: 'targetreadyrposeconds', title: this.$t('label.dr.target.rpo'), dataIndex: 'targetreadyrposeconds' }
      ]
    }
  },
  watch: {
    planId () { this.fetchData() }
  },
  created () { this.fetchData() },
  methods: {
    fetchData () {
      if (!this.planId) {
        this.checkpoints = []
        return
      }
      const hasNewApi = 'listDrSyncCheckpoints' in this.$store.getters.apis
      const hasLegacyApi = 'listDrRestorePoints' in this.$store.getters.apis
      if (!hasNewApi && !hasLegacyApi) {
        this.checkpoints = []
        return
      }
      this.loading = true
      const request = hasNewApi ? listDrSyncCheckpoints : listDrRestorePoints
      request({ planid: this.planId }).then(result => {
        this.checkpoints = (result.items || []).map(item => ({
          ...item,
          transferratio: this.transferRatio(item)
        }))
      }).finally(() => { this.loading = false })
    },
    formatSeconds (seconds) {
      const value = Number(seconds)
      if (!Number.isFinite(value)) return '-'
      if (value < 60) return `${Math.round(value)}s`
      if (value < 3600) return `${Math.round(value / 60)}m`
      if (value < 86400) return `${Math.round(value / 3600)}h`
      return `${Math.round(value / 86400)}d`
    },
    formatBytes (bytes) {
      const value = Number(bytes)
      if (!Number.isFinite(value)) return '-'
      const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
      let amount = value
      let index = 0
      while (Math.abs(amount) >= 1024 && index < units.length - 1) {
        amount /= 1024
        index += 1
      }
      return `${amount.toFixed(index === 0 ? 0 : 1)} ${units[index]}`
    },
    formatDuration (milliseconds) {
      const value = Number(milliseconds)
      if (!Number.isFinite(value)) return '-'
      return this.formatSeconds(value / 1000)
    },
    transferRatio (checkpoint) {
      const payload = Number(checkpoint.transferpayloadbytes)
      const virtual = Number(checkpoint.virtualbytes)
      return Number.isFinite(payload) && Number.isFinite(virtual) && virtual > 0 ? payload / virtual : null
    },
    formatTransferRatio (ratio) {
      const value = Number(ratio)
      return Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '-'
    }
  }
}
</script>

<style lang="less">
.cross-dr-checkpoint-note {
  margin-bottom: 12px;
}
</style>
