<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0. -->
<template>
  <div class="storage-nfs-listeners">
    <p class="nfs-listener-hint">{{ $t('message.storage.nfs.listeners.scope') }} · {{ $t('label.storage.nfs.listeners.groups') }} {{ model.groupCount }}</p>
    <a-table
      size="small"
row-key="key"
class="nfs-listener-table"
      :columns="columns"
:data-source="model.rows"
:loading="refreshing"
      :pagination="{ pageSize: 20, hideOnSinglePage: true, showTotal: total => $t('label.storage.nfs.listeners.addresses') + ' ' + total }"
      :scroll="{ x: 1450 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'address'">
          <code>{{ record.address || '—' }}</code>
          <div class="nfs-listener-hint">{{ record.family }} · {{ roleLabel(record.role) }}</div>
          <div v-if="record.addressSource !== 'API'" class="nfs-listener-hint">{{ $t('label.storage.nfs.listeners.source.' + record.addressSource.toLowerCase()) }}</div>
        </template>
        <template v-else-if="column.key === 'mode'">
          <template v-if="record.observedMode !== 'UNKNOWN'">
            <a-tag>NFSv4</a-tag><a-tag v-if="record.observedMode === 'V3V4_DUAL'">NFSv3</a-tag>
            <div v-if="['STALE', 'UNAVAILABLE'].includes(record.state)" class="nfs-listener-hint">{{ $t('label.storage.nfs.listeners.last') }}</div>
          </template>
          <span v-else>{{ $t('label.unknown') }}</span>
          <div class="nfs-listener-hint">{{ $t('label.storage.nfs.listeners.configured') }}: {{ group(record).configuredModeRaw }}</div>
          <div v-if="group(record).modeConflict" class="nfs-listener-warning">{{ $t('message.storage.nfs.listeners.mode.conflict') }}</div>
        </template>
        <template v-else-if="column.key === 'binding'">
          <span>{{ record.configuredBind }}</span>
          <div class="nfs-listener-hint">{{ $t('label.storage.nfs.listeners.observed') }}: {{ record.actualBind }}</div>
          <div v-if="!group(record).registered" class="nfs-listener-warning">{{ $t('message.storage.nfs.listeners.unregistered') }}</div>
        </template>
        <template v-else-if="column.key === 'state'">
          <a-badge :status="listenerBadgeStatus(record.state)" :text="stateLabel(record.state)" />
          <div class="nfs-listener-hint">{{ record.observedAt || '—' }}</div>
          <div v-if="record.partial" class="nfs-listener-warning">{{ $t('message.storage.nfs.listeners.partial') }}</div>
        </template>
        <template v-else-if="column.key === 'exports'">
          <a-button type="link" size="small" @click="open(record.groupKey)">{{ group(record).linkedResourceCount ?? $t('label.unknown') }}</a-button>
          <div v-if="group(record).referenceMismatch" class="nfs-listener-warning">{{ $t('message.storage.nfs.listeners.references') }}</div>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-space :size="4">
            <a-button size="small" @click="open(record.groupKey)">{{ $t('label.details') }}</a-button>
            <a-tooltip :title="$t(record.canDelete ? 'message.storage.nfs.listeners.delete.group' : 'message.storage.nfs.listeners.delete.blocked')">
              <span><a-button size="small" danger :disabled="!record.canDelete" @click="deleteGroup(record)"><DeleteOutlined />{{ $t('label.delete') }}</a-button></span>
            </a-tooltip>
          </a-space>
        </template>
      </template>
    </a-table>
    <MoldDialog :visible="visible" :title="$t('label.storage.nfs.listeners.details')" size="lg" @cancel="close">
      <div class="nfs-listener-detail">
        <a-alert show-icon type="info" :message="$t('message.storage.nfs.listeners.readonly')" />
        <a-alert v-if="selectedIncomplete" class="nfs-listener-section" show-icon type="warning" :message="$t('message.storage.nfs.listeners.references')" />
        <section v-for="item in selectedGroups" :key="item.key" class="nfs-listener-section">
          <a-descriptions bordered size="small" :column="1">
            <a-descriptions-item :label="$t('label.id')"><code>{{ item.id || '—' }}</code></a-descriptions-item>
            <a-descriptions-item :label="$t('label.port')">{{ item.port || '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.nfs.listeners.configured')">{{ item.configuredModeRaw }} / {{ item.listenIp }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.nfs.listeners.observed')">{{ item.observation.observedMode || 'UNKNOWN' }} / {{ item.observation.listenIp || '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.state')">{{ stateLabel(item.state) }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.nfs.listeners.sources')">{{ item.observation.observations.map(entry => entry.source + ': ' + (entry.observedAt || '—') + ' / ' + entry.state).join(' · ') || '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.nfs.listeners.exports')">{{ item.linkedResourceCount ?? $t('label.unknown') }} · {{ item.exportNames.join(', ') || '—' }}</a-descriptions-item>
          </a-descriptions>
        </section>
        <a-table class="nfs-listener-table nfs-listener-section" size="small" row-key="key" :columns="detailColumns" :data-source="selectedRows"  :scroll="{ x: 850 }">
          <template #bodyCell="{ column, record }">
            <code v-if="column.key === 'endpoint'">{{ record.endpoint }}</code>
            <template v-else-if="column.key === 'state'">{{ stateLabel(record.state) }}</template>
            <template v-else-if="column.key === 'role'">{{ record.family }} · {{ roleLabel(record.role) }}</template>
          </template>
        </a-table>
        <p class="nfs-listener-hint">{{ $t('message.storage.nfs.listeners.scope') }}</p>
      </div>
    </MoldDialog>
  </div>
</template>
<script>
import SharedFSTable from '@/components/storage/SharedFSTable.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import { DeleteOutlined } from '@ant-design/icons-vue'
export default {
  components: { ATable: SharedFSTable, MoldDialog, DeleteOutlined },
  props: {
    model: { type: Object, required: true },
    refreshing: Boolean,
    scope: { type: String, default: '' }
  },
  emits: ['delete-group'],
  data () { return { visible: false, selectedKey: null, selectedExport: null } },
  computed: {
    groupsByKey () { return Object.fromEntries(this.model.groups.map(group => [group.key, group])) },
    selectedIncomplete () { return this.selectedExport ? this.model.exportGroups[this.selectedExport]?.incomplete !== false : false },
    selectedGroups () {
      if (this.selectedExport) return this.model.exportGroups[this.selectedExport]?.groups || []
      return this.groupsByKey[this.selectedKey] ? [this.groupsByKey[this.selectedKey]] : []
    },
    selectedRows () { return this.selectedGroups.flatMap(group => group.rows) },
    columns () {
      return [
        { title: this.$t('label.storage.nfs.listeners.address'), key: 'address', fixed: 'left', width: 250 },
        { title: this.$t('label.port'), key: 'port', dataIndex: 'port', width: 80 },
        { title: this.$t('label.storage.service.nfs.protocol.mode'), key: 'mode', width: 190 },
        { title: this.$t('label.storage.nfs.listeners.binding'), key: 'binding', width: 220 },
        { title: this.$t('label.storage.nfs.listeners.observation'), key: 'state', width: 230 },
        { title: this.$t('label.storage.nfs.listeners.exports'), key: 'exports', width: 140 },
        { title: this.$t('label.actions'), key: 'actions', fixed: 'right', width: 180 }
      ]
    },
    detailColumns () {
      return [
        { title: this.$t('label.storage.service.ip.port'), key: 'endpoint', dataIndex: 'endpoint', width: 310 },
        { title: this.$t('label.storage.nfs.listeners.address'), key: 'role', width: 160 },
        { title: this.$t('label.storage.nfs.listeners.observed'), dataIndex: 'observedMode', width: 150 },
        { title: this.$t('label.state'), key: 'state', width: 190 }
      ]
    }
  },
  watch: {
    scope () { this.close() },
    model () { if (this.visible && !this.selectedExport && !this.groupsByKey[this.selectedKey]) this.close() }
  },
  methods: {
    group (row) { return this.groupsByKey[row.groupKey] },
    stateLabel (state) { return this.$t('label.storage.nfs.listeners.state.' + state.toLowerCase()) },
    listenerBadgeStatus (state) { return ({ READY: 'success', FAILED: 'error', CONFLICT: 'warning', STALE: 'warning', UNAVAILABLE: 'warning' })[state] || 'default' },
    roleLabel (role) { return this.$t('label.storage.nfs.listeners.role.' + (['PRIMARY', 'ALIAS', 'SECONDARY', 'DEDICATED'].includes(role) ? role.toLowerCase() : 'unknown')) },
    open (key) { this.selectedKey = key; this.selectedExport = null; this.visible = true },
    openExport (id) { this.selectedKey = null; this.selectedExport = id; this.visible = true },
    close () { this.visible = false; this.selectedKey = null; this.selectedExport = null },
    deleteGroup (row) { if (row.canDelete && this.group(row)?.deleteRecord) this.$emit('delete-group', this.group(row).deleteRecord) }
  }
}
</script>
<style scoped>
.storage-nfs-listeners { min-width: 0; }
.nfs-listener-hint { color: var(--ui-text-secondary); font-size: 12px; line-height: 1.7; margin: 4px 0; overflow-wrap: anywhere; }
.nfs-listener-warning { color: var(--ui-warning-text); font-size: 12px; line-height: 1.7; }
.nfs-listener-section { margin-top: 16px; }
.nfs-listener-detail { min-width: 0; }
code { white-space: normal; overflow-wrap: anywhere; color: inherit; }
.nfs-listener-table :deep(.ant-table-cell) { vertical-align: top; }
.nfs-listener-table :deep(th.ant-table-cell-fix-left),
.nfs-listener-table :deep(th.ant-table-cell-fix-right) { background: var(--ui-bg-elevated) !important; color: var(--ui-text-primary); }
.nfs-listener-table :deep(td.ant-table-cell-fix-left),
.nfs-listener-table :deep(td.ant-table-cell-fix-right) { background: var(--ui-bg-surface) !important; }
</style>
