<!-- Licensed under the Apache License, Version 2.0. See NOTICE. -->
<template>
  <div v-if="rows.length || unavailable" class="nfs-local-probe-panel">
    <a-alert
      v-if="pending.length && !unavailable"
      type="info"
show-icon
      :message="$t('label.storage.nfs.external.check')"
      :description="$t('message.storage.nfs.external.check') + ' ' + pending.slice(0, 3).join(', ')" />
    <a-alert
v-if="unavailable"
type="warning"
show-icon
      :message="$t('label.storage.nfs.probe.unknown')"
      :description="$t('message.storage.nfs.probe.unknown')" />
    <a-table
:columns="columns"
:data-source="rows"
:row-key="rowKey"
size="small"
      :pagination="{ pageSize: 10, hideOnSinglePage: true }"
:scroll="{ x: 1000 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'configuration'">{{ configurationLabel(record) }}</template>
        <template v-else-if="column.key === 'local'">
          <a @click="selected = record">{{ localLabel(record.status) }}</a>
        </template>
        <template v-else-if="column.key === 'external'">{{ $t('label.storage.nfs.probe.external') }}</template>
        <template v-else-if="column.key === 'observed'">{{ observed(record.observedAt) }}<span v-if="record.status === 'UNKNOWN'"> · {{ $t('label.storage.boot.previous') }}</span></template>
      </template>
    </a-table>
    <MoldDialog v-if="selected" :visible="true" :title="$t('label.storage.nfs.probe.details')" size="lg" @cancel="selected = null">
      <a-alert
show-icon
:type="selected.status === 'FAILED' ? 'error' : 'info'"
        :message="localLabel(selected.status)"
:description="$t('message.storage.nfs.probe.preserve')" />
      <a-descriptions class="probe-section" bordered :column="1" size="small">
        <a-descriptions-item :label="$t('label.storage.service.export.name')">{{ selected.name }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.port')">{{ selected.port }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.nfs.probe.configuration')">{{ configurationLabel(selected) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.nfs.probe.local')">{{ localLabel(selected.status) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.nfs.probe.external.column')">{{ $t('label.storage.nfs.probe.external') }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.reason')">{{ selected.reason || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.nfs.probe.method')">{{ selected.applyMethod || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.observed')">{{ observed(selected.observedAt) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.identity')">{{ selected.bootId || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.nfs.probe.operation')">{{ selected.operationid || '—' }}</a-descriptions-item>
      </a-descriptions>
      <template #footer><a-button @click="selected = null">{{ $t('label.close') }}</a-button></template>
    </MoldDialog>
  </div>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog'
export default {
  name: 'NfsLocalProbeNotice',
  components: { MoldDialog },
  props: {
    shares: { type: Array, default: () => [] },
    unavailable: { type: Boolean, default: false }
  },
  data () { return { selected: null } },
  computed: {
    pending () {
      return this.shares.filter(row => row.localprobe?.externalclientrequired === true)
        .map(row => row.name + ' (' + (row.localprobe.ports || [])
          .filter(port => port.status === 'EXTERNAL_CLIENT_REQUIRED').map(port => port.port).join(', ') + ')')
    },
    rows () {
      return this.shares.flatMap(share => (share.localprobe?.ports || []).map(row => ({
        ...row,
        resource: share.id,
        name: share.name,
        status: this.unavailable ? 'UNKNOWN' : row.status
      })))
    },
    columns () {
      return [['name', 'label.storage.service.export.name', 180],
        ['port', 'label.port', 90], ['configuration', 'label.storage.nfs.probe.configuration', 160],
        ['local', 'label.storage.nfs.probe.local', 190], ['external', 'label.storage.nfs.probe.external.column', 190],
        ['observed', 'label.storage.boot.observed', 220]]
        .map(([key, title, width]) => ({ key, dataIndex: key, title: this.$t(title), width }))
    }
  },
  watch: {
    shares () { this.selected = null },
    unavailable () { this.selected = null }
  },
  methods: {
    rowKey (row) { return [row.resource, row.name, row.port].join(':') },
    localLabel (status) {
      return this.$t('label.storage.nfs.probe.' + ({
        EXTERNAL_CLIENT_REQUIRED: 'excluded', MOUNT_VERIFIED: 'verified', FAILED: 'failed'
      }[status] || 'unknown'))
    },
    configurationLabel (row) {
      return this.$t(row.status === 'UNKNOWN' ? 'label.storage.nfs.probe.unknown' : row.configurationState === 'APPLIED'
        ? 'label.storage.nfs.probe.applied' : 'label.storage.nfs.probe.unknown')
    },
    observed (value) {
      if (value === null || value === undefined || value === '') return '—'
      const date = new Date(typeof value === 'number' ? value * 1000 : value)
      return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString()
    }
  }
}
</script>
<style scoped>
.nfs-local-probe-panel { margin-bottom: 16px; color: var(--ui-text-primary); }
.nfs-local-probe-panel > .ant-alert { margin-bottom: 16px; }
.probe-section { margin-top: 16px; }
</style>
